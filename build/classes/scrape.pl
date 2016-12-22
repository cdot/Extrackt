# Scrape programme information for a bbc programme identified
# by PID
use strict;

# MP3 tags
# Name			Tag	Used	Comment
# ALBUM			TALB	Y	
# ALBUMSORT		TSOA	-      	Album Sort Order
# ALBUMARTIST		TPE2	-
# ALBUMARTISTSORT	TSO2	-	Album Artist Sort Order		
# ARTIST		TPE1	Y	Author
# ARTISTSORT		TSOP	-	ArtistSortOrder	 	 	 
# BPM			TBPM	-	Beats per minute	 
# COMMENT		COMM	-
# COMPILATION		TCMP	-	Part of a compilation	 	 
# COMPOSER		TCOM	Y
# COMPOSERSORT		TSOC	-	Composer Sort Order		
# CONDUCTOR		TPE3	Y
# CONTENTGROUP		TIT1	-	Music category description	 
# COPYRIGHT		TCOP	-
# DISCNUMBER		TPOS	-
# ENCODEDBY		TENC	-
# ENCODERSETTINGS	TSSE	-
# ENCODINGTIME		TDEN	-
# FILEOWNER		TOWN	-
# FILETYPE		TFLT	-
# GENRE			TCON	-
# INITIALKEY		TKEY	-
# INVOLVEDPEOPLE	IPLS	-
# ISRC			TSRC	-
# LANGUAGE		TLAN	-
# LENGTH		TLEN	-
# LYRICIST		TEXT	-
# MEDIATYPE		TMED	-
# MIXARTIST		TPE4	-
# MOOD	 		TMOO	-
# MUSICIANCREDITS 	TMCL	-	 	 	 	 
# NETRADIOOWNER		TRSO	-
# NETRADIOSTATION	TRSN	-
# ORIGALBUM		TOAL	-
# ORIGARTIST		TOPE	-
# ORIGFILENAME		TOFN	-
# ORIGLYRICIST		TOLY	-
# ORIGYEAR		TORY	-
# PODCAST		PCST	-
# PODCASTCATEGORY	TCAT	-
# PODCASTDESC		TDES	-
# PODCASTID		TGID	-
# PODCASTURL		WFED	-
# POPULARIMETER		POPM	-
# PUBLISHER		TPUB	-
# RATING MM		POPM	-
# RATING WMP		POPM	-
# RELEASETIME		TDRL	-
# SETSUBTITLE	 	TSST	-	 	 	 	 
# SUBTITLE		TIT3	-
# TAGGINGTIME	 	TDTG	-	 	 	 	 
# TITLE			TIT2	Y
# TITLESORT		TSOT	-
# TRACK			TRCK	-
# UNSYNCEDLYRICS	USLT	-
# WWW			WXXX	-	Other Web sites	URL
# WWWARTIST		WOAR	-
# WWWAUDIOFILE		WOAF	-	Official audio file information	 
# WWWAUDIOSOURCE	WOAS	-	Official audio source	 
# WWWCOMMERCIALINFO	WCOM	-	PromotionURL	 	 	 
# WWWCOPYRIGHT		WCOP	-	CopyrightURL	 	 	 
# WWWPAYMENT		WPAY	-
# WWWPUBLISHER		WPUB	-
# WWWRADIOPAGE		WORS	-
# YEAR			TYER	-
# TDAT			TDRC	-
# Other fields		TXXX	-	Field name	 	 	 

# number: startf (fadein) .. endf (fadeout)
use LWP;
use LWP::UserAgent;
use HTTP::Request;
use HTML::Entities;

my $pid = $ARGV[0];
print "Scraping http://www.bbc.co.uk/programmes/$pid\n";
my $req = HTTP::Request->new(GET => "http://www.bbc.co.uk/programmes/$pid");
my $ua = LWP::UserAgent->new;
$ua->agent("TrackMinder/1.0 ");

# Pass request to the user agent and get a response back
my $res = $ua->request($req);

my $text = $res->content;

die $res->status_line unless ($res->is_success);

#binmode STDOUT, ":utf8";

my $duration = 1.5 * 60 * 60; # Normally 1.5h

if ($text =~ /Listen now.*?(\d+) mins/) {
    $duration = $1 * 60;
}
$text =~ /class="count">(\d+)/s;
my $count = $1;
$text =~ s%.*(<ul class="segments".*?</ul>).*%$1%s;
my @bits = split(/<\/li>/, $text);
pop(@bits); # last one is empty
my @tracks;
for (my $i = 0; $i < $count && $i < scalar(@bits); $i++) {
    my %info;
    my $d = $bits[$i];
    
    if ($d =~ m%<div[^>]*\bclass="play-time[\s"].*?>(.*?)</div>%s) {
	my $s = $1;
	# Convert HH:MM to s
	if ($s =~ /^(\d+):(\d+)$/) {
	    $s = ((int($1) * 60) + int($2)) * 60;
            $info{_START} = $s;
	} else {
	    print STDERR "Bad time $s\n";
	}
    }
    if ($d =~ m%<span[^>]*\bclass="artist[\s"].*?>(.*?)</span>%s) {
	# Should really be TPE1
        $info{TCOM} = HTML::Entities::decode_entities($1);
    }
    while ($d =~ s/Performer:(?:\s*<.*?>)?\s*(.*?)\s*[.<]//s) {
	# Should really be TPE2
	my $perf = HTML::Entities::decode_entities($1);
	if ($info{TPE1}) {
	    $info{TPE1} .= ", $perf";
	} else {
	    $info{TPE1} = $perf;
	}
    }
    while ($d =~ s/Conductor:(?:\s*<.*?>)?\s*(.*?)\s*[.<]//s) {
	$info{TPE3} = HTML::Entities::decode_entities($1);
    }
    if ($d =~ m%<span[^>]*\bclass="title[\s"].*?>\s*(.*?)\s*</span>%s) {
	$info{TIT2} = HTML::Entities::decode_entities($1);
    }
    if ($d =~ m%<span typeof="mo:Record">.*dc:title">\s*(.*?)\s*</span>%s) {
	$info{TALB} = HTML::Entities::decode_entities($1);
    }
    $tracks[$i] =\%info;
    if ($i > 0) {
	$tracks[$i - 1]{_END} = $tracks[$i]{_START};
    }
}
$tracks[$#tracks]{_END} = $duration if scalar(@tracks);

foreach my $track (@tracks) {

    print "$track->{_START} .. $track->{_END}\n";
    foreach my $k ( grep { !/^_/ } keys %$track ) {
	print "$k: $track->{$k}\n";
    }
    print "\n";
}


